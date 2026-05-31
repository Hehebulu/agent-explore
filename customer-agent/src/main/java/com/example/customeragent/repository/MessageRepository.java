package com.example.customeragent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.customeragent.model.Message;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageRepository extends BaseMapper<Message> {
}
