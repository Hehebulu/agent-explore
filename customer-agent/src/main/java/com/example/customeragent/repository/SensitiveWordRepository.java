package com.example.customeragent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.customeragent.model.SensitiveWord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SensitiveWordRepository extends BaseMapper<SensitiveWord> {
}
